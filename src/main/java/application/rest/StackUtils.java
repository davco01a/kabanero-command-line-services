package application.rest;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.http.HttpResponse;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.yaml.snakeyaml.Yaml;

import io.kabanero.v1alpha2.client.apis.KabaneroApi;
import io.kabanero.v1alpha2.models.Stack;
import io.kabanero.v1alpha2.models.StackList;
import io.kabanero.v1alpha2.models.StackSpec;
import io.kabanero.v1alpha2.models.StackSpecImages;
import io.kabanero.v1alpha2.models.StackSpecPipelines;
import io.kabanero.v1alpha2.models.StackSpecVersions;
import io.kabanero.v1alpha2.models.StackStatus;
import io.kabanero.v1alpha2.models.StackStatusVersions;
import io.kabanero.v1alpha2.models.Kabanero;
import io.kabanero.v1alpha2.models.KabaneroList;
import io.kabanero.v1alpha2.models.KabaneroSpecStacks;
import io.kabanero.v1alpha2.models.KabaneroSpecStacksRepositories;
import io.kubernetes.client.ApiClient;
import io.kubernetes.client.apis.CoreV1Api;
import io.kubernetes.client.models.V1ContainerStatus;
import io.kubernetes.client.models.V1Pod;
import io.kubernetes.client.models.V1PodList;
import io.kubernetes.client.models.V1PodStatus;
import io.kubernetes.client.proto.Meta.Status;

public class StackUtils {
	
	public static boolean readGitSuccess=true;
	
	public static Comparator<Map<String, String>> mapComparator = new Comparator<Map<String, String>>() {
	    public int compare(Map<String, String> m1, Map<String, String> m2) {
	        return m1.get("name").compareTo(m2.get("name"));
	    }
	};
	
	

	private static Map readYaml(String response) {
		Yaml yaml = new Yaml();
		Map<String, Object> obj = null;
		try {
			InputStream inputStream = new ByteArrayInputStream(response.getBytes(StandardCharsets.UTF_8));
			obj = yaml.load(inputStream);
		} catch (Exception e) {
			e.printStackTrace();
		}
		return obj;
	}
	
	private static boolean pause() {
        // Sleep for half a second before next try
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            // Something woke us up, most probably process is exiting.
            // Just break out of the loop to report the last DB exception.
            return true;
        }
        return false;
    }

	public static String getFromGit(String url, String user, String pw) {
		HttpClientBuilder clientBuilder = HttpClients.custom();
		CredentialsProvider credsProvider = new BasicCredentialsProvider();
		credsProvider.setCredentials(new AuthScope(null, -1), new UsernamePasswordCredentials(user, pw));
		clientBuilder.setDefaultCredentialsProvider(credsProvider);
		HttpClient client = clientBuilder.create().build();
		HttpGet request = new HttpGet(url);
		request.addHeader("accept", "application/yaml");
		// add request header

		HttpResponse response = null;
		IOException savedEx=null;
		int retries = 0;
		for (; retries < 10; retries++) {
			try {
				response = client.execute(request);
				readGitSuccess=true;
				break;
			} catch (IOException e) {
				e.printStackTrace();
				savedEx=e;
			}
			if (pause()) {
				break;
			}
		}
		if (retries >= 10) {
			readGitSuccess=false;
			throw new RuntimeException("Exception connecting or executing REST command to Git url: "+url, savedEx);
		}
		System.out.println("Response Code : " + response.getStatusLine().getStatusCode());
		if (response.getStatusLine().getStatusCode()==429) {
			return "http code 429: Github retry Limited Exceeded, please try again in 2 minutes";
		}
		BufferedReader rd = null;
		try {
			rd = new BufferedReader(new InputStreamReader(response.getEntity().getContent()));
		} catch (IllegalStateException | IOException e) {
			e.printStackTrace();
		}
		StringBuffer result = new StringBuffer();
		String line = "";
  
		try {
			while ((line = rd.readLine()) != null) {
				result.append(line + "\n");
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		return result.toString();
	}
	
	public static String getImage(String namespace) throws Exception {
		String image=null;
		ApiClient apiClient = KubeUtils.getApiClient();
		CoreV1Api api = new CoreV1Api();
		V1PodList list = api.listNamespacedPod(namespace, false, null, null, "", "", 30, null, 60, false);

		for (V1Pod item : list.getItems()) {
			if (item.getMetadata().getName().contains("kabanero-cli")) {
				V1PodStatus status = (V1PodStatus)item.getStatus();
				List<V1ContainerStatus> containerStatuses =  status.getContainerStatuses();

				for (V1ContainerStatus containerStatus : containerStatuses) {
					if ("kabanero-cli".contentEquals(containerStatus.getName())) {
						image = containerStatus.getImage();
					}
				}


			}
		}
		image = image.replace("docker.io/", "");
		return image;
	}
	
	

	public static Kabanero getKabaneroForNamespace(String namespace) {
		String url = null;
		try {
			ApiClient apiClient = KubeUtils.getApiClient();
			KabaneroApi api = new KabaneroApi(apiClient);
			KabaneroList kabaneros = api.listKabaneros(namespace, null, null, null);
			List<Kabanero> kabaneroList = kabaneros.getItems();
			if (kabaneroList.size() > 0) {
				return kabaneroList.get(0);
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}
	
	
	
	public static List getStackFromGIT(String user, String pw, String url) {
		String response = null;
		try {
			response = getFromGit(url, user, pw);
			if (response!=null) {
				if (response.contains("http code 429:")) {
					ArrayList<String> list= new ArrayList();
					list.add(response);
					return list;
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
			throw e;
		}
		//System.out.println("response = " + response);
		ArrayList<Map> list = null;
		try {
			Map m = readYaml(response);
			list = (ArrayList<Map>) m.get("stacks");
		} catch (Exception e) {
			e.printStackTrace();
		}
		return list;
	}
	
	
	

	public static List streamLineMasterMap(List<Map> list) {
		ArrayList aList = new ArrayList();
		for (Map map : list) {
			String name = (String) map.get("id");
			String version = (String) map.get("version");
			List<Map> images = (List<Map>) map.get("images");
			List<StackSpecImages> stackSpecImages = new ArrayList<StackSpecImages>();
			for (Map image: images) {
				StackSpecImages stackSpecImage = new StackSpecImages();
				stackSpecImage.setImage((String) image.get("image"));
				stackSpecImages.add(stackSpecImage);
			}
			Map imageMap=(Map)images.get(0);
			HashMap outMap = new HashMap();
			outMap.put("name", name);
			outMap.put("version", version);
			outMap.put("images", stackSpecImages);
			aList.add(outMap);
		}
		return aList;
	}
	

	
	public static List allStacks(StackList fromKabanero) {
		ArrayList<Map> allStacks = new ArrayList<Map>();
		try {
			for (Stack s : fromKabanero.getItems()) {
				HashMap allMap = new HashMap();
				//System.out.println("working on one collection: " + s);
				String name = s.getMetadata().getName();
				name = name.trim();
				List<StackStatusVersions> versions = s.getStatus().getVersions();
				List<Map> status = new ArrayList<Map>();
				for (StackStatusVersions stackStatusVersion : versions) {
					HashMap versionMap = new HashMap();
					versionMap.put("status", stackStatusVersion.getStatus());
					versionMap.put("version", stackStatusVersion.getVersion());
					status.add(versionMap);
				}
				allMap.put("name", name);
				allMap.put("status",status);
				//System.out.println("all map: " + allMap);
				allStacks.add(allMap);
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		return allStacks;
	}
	
	
	
	
	

	public static List filterNewStacks(List<Map> fromGit, StackList fromKabanero) {
		ArrayList<Map> newStacks = new ArrayList<Map>();
		ArrayList<Map> registerVersionForName = new ArrayList<Map>();
		try {
			for (Map map : fromGit) {
				String name = (String) map.get("id");
				String version = (String) map.get("version");
				name = name.trim();
				version = version.trim();
				boolean match = false;
				HashMap gitMap = new HashMap();
				// check this git map against all of the kab stacks
				for (Stack kabStack : fromKabanero.getItems()) {
					String name1 = (String) kabStack.getSpec().getName();
					List<StackStatusVersions> versions = kabStack.getStatus().getVersions();
					name1 = name1.trim();
					// see if name from git matches name from kabanero
					// there can be multiple git maps that have the same name but different versions
					if (name1.contentEquals(name)) {
						System.out.println("name="+name+",git version="+version+",versions="+versions);
						// check if the version from the git map occurs in the list of versions
						// for this name matched stack map
						for (StackStatusVersions stackStatusVersions : versions) {
							if (version.contentEquals(stackStatusVersions.getVersion())) {
								match = true;
								HashMap versionForName = new HashMap();
								versionForName.put(name, version);
								registerVersionForName.add(versionForName);
								break;
							}
						}
					}
				}
				if (!match) {
					gitMap.put("name", (String)map.get("id"));
					gitMap.put("version", version);
					gitMap.put("desiredState", "active");
					gitMap.put("images", map.get("images"));
					newStacks.add(gitMap);
				}
			}
			System.out.println("newStacks: "+newStacks);
			System.out.println("registerVersionForName: "+registerVersionForName);
			// clean new stacks of any versions that were added extraneously
			for (Map newStack:newStacks) {
				boolean versionAlreadyThereFromGit = false;
				String name = (String) newStack.get("name");
				for (Map versionForName:registerVersionForName) {
					String version = (String) versionForName.get(name);
					String newStackVersion = (String) newStack.get("version");
					if (version!=null) {
						if (version.contentEquals(newStackVersion)) {
							versionAlreadyThereFromGit=true;
						}
					}
				}
				if (versionAlreadyThereFromGit) {
					System.out.println("removing: "+newStack);
					newStacks.remove(newStack);
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		return newStacks;
	}
	
	
	public static List filterStacksToActivate(List<Map> fromGit, StackList fromKabanero) {
		ArrayList<Map> activateCollections = new ArrayList<Map>();
		try {
			for (Map map : fromGit) {
				String name = (String) map.get("id");
				String version = (String) map.get("version");
				name = name.trim();
				version = version.trim();
				boolean match = false;
				HashMap activateMap = new HashMap();
				for (Stack s : fromKabanero.getItems()) {
					String name1 = s.getMetadata().getName();
					name1 = name1.trim();
					StackStatus status = s.getStatus();
					List<StackStatusVersions> stackVersions=status.getVersions();
					for (StackStatusVersions stackVersion:stackVersions) {
						if (name1.contentEquals(name) && version.contentEquals(stackVersion.getVersion()) && "inactive".contentEquals(stackVersion.getStatus())) {
							activateMap.put("name", map.get("id"));
							activateMap.put("version", stackVersion.getVersion());
							activateMap.put("desiredState","active");
							activateMap.put("images", map.get("images"));
							activateCollections.add(activateMap);
						}
					}
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		return activateCollections;
	}
	
	
	
	
	public static List<StackSpecVersions> getKabInstanceVersions(StackList fromKabanero, String name) {
		for (Stack s : fromKabanero.getItems()) {
			if (s.getSpec().getName().contentEquals(name)) {
				return s.getSpec().getVersions();
			}
		}
		return null;
	}
	
	public static Stack getKabInstance(StackList fromKabanero, String name) {
		for (Stack s : fromKabanero.getItems()) {
			if (s.getSpec().getName().contentEquals(name)) {
				return s;
			}
		}
		return null;
	}
	

	
	
	
	
	public static boolean isStackVersionInGit(List<Map> fromGit, String version, String name) {
		System.out.println("isStackVersionInGit");
		System.out.println("input parms - name: "+name+" version: "+version);
		try {
			for (Map map1 : fromGit) {
				String name1 = (String) map1.get("name");
				name1 = name1.trim();
				if (name1.contentEquals(name)) {
					List<Map> versions = (List<Map>) map1.get("versions");
					System.out.println("versions: "+versions);
					for (Map versionElement:versions) {
						String versionValue = (String) versionElement.get("version");
						if (version.equals(versionValue)) {
							return true;
						}
					}
				} 
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		return false;
	}

	public static List filterDeletedStacks(List<Map> fromGit, StackList fromKabanero) {
		ArrayList<Map> stacksToDelete = new ArrayList<Map>();
		String name = null;
		String version = null;
		boolean stackInGit;
		boolean match;
		try {
			for (Stack kabStack: fromKabanero.getItems()) {
				Map kabMap = new HashMap();
				match = false;
				List<StackSpecVersions> stackSpecVersions = kabStack.getSpec().getVersions();
				String kabName = kabStack.getSpec().getName();
				for (StackSpecVersions stackSpecVersion:stackSpecVersions) {
					String kabVersion=stackSpecVersion.getVersion();
					for (Map map1 : fromGit) {
						String gitName = (String) map1.get("id");
						gitName = gitName.trim();
						String gitVersion = (String) map1.get("version");
						name = (String) kabStack.getSpec().getName();
						if (gitName.contentEquals(kabName)) {
							stackInGit=true;
							// If this Kabanero Stack version does not match GIT hub stack version, add it for deletion 
							// if this version is 
							if (kabVersion.equals(gitVersion)) {
								version=gitVersion;
								match=true;
								break;
							}
						} 
					}
					String stat=" is not";
					if (match) {
						stat=" is";
					} 
					System.out.println("Kab Stack name: "+kabName+" version number: "+kabVersion+stat+" found in list of GIT versions");
					System.out.println("Version list is: "+stackSpecVersions);
					if (!match) {
						kabMap.put("name", kabName);
						kabMap.put("version",kabVersion);
						stacksToDelete.add(kabMap);
					}
				}

			}
		} catch (Exception e) {
			e.printStackTrace();
		}
		return stacksToDelete;
	}
	
	
	
	public static List<Map> packageStackMaps(List<Map> stacks) {
		ArrayList<Map> updatedStacks = new ArrayList<Map>();
		ArrayList<Map> versions = null;
		String saveName = "";
		for (Map stack : stacks) {
			System.out.println("packageStackMaps one stack: "+stack.toString());
			String name = (String) stack.get("name");
			// append versions and desiredStates to stack
			if (name.contentEquals(saveName)) {
				HashMap versionMap = new HashMap();
				versionMap.put("version", (String) stack.get("version"));
				versionMap.put("images", stack.get("images"));
				versions.add(versionMap);
			} 
			// creating stack object to add to new stacks List
			else {
				saveName = name;
				versions = new ArrayList<Map>();
				HashMap map = new HashMap();
				map.put("versions",versions);
				map.put("name",name);
				HashMap versionMap = new HashMap();
				versionMap.put("version", (String) stack.get("version"));
				versionMap.put("images", stack.get("images"));
				versions.add(versionMap);
				updatedStacks.add(map);
			}
		}
		return updatedStacks;
	}
	
	
	
	public static List<Stack> packageStackObjects(List<Map> stacks, Map versionedStackMap) {
		ArrayList<Stack> updateStacks = new ArrayList<Stack>();
		ArrayList<StackSpecVersions> versions = null;
		StackSpec stackSpec = null;
		String saveName = "";
		System.out.println("versionedStackMap: "+versionedStackMap);
		for (Map stack : stacks) {
			System.out.println("packageStackObjects one stack: "+stack);
			String name = (String) stack.get("name");
			String version = (String) stack.get("version");
			System.out.println("packageStackObjects version="+version);
			// append versions and desiredStates to stack
			if (name.contentEquals(saveName)) {
				StackSpecVersions specVersion = new StackSpecVersions();
				specVersion.setDesiredState("active");
				specVersion.setVersion((String) stack.get("version"));
				specVersion.setImages((List<StackSpecImages>) stack.get("images"));
				specVersion.setPipelines((List<StackSpecPipelines>) versionedStackMap.get(name));
				versions.add(specVersion);
			} 
			// creating stack object to add to new stacks List
			else {
				saveName = name;
				versions = new ArrayList<StackSpecVersions>();
				stackSpec = new StackSpec();
				stackSpec.setVersions(versions);
				stackSpec.setName(name);
				Stack stackObj = new Stack();
				stackObj.setKind("Stack");
				stackObj.setSpec(stackSpec);
				StackSpecVersions specVersion = new StackSpecVersions();
				specVersion.setDesiredState("active");
				specVersion.setVersion(version);
				specVersion.setImages((List<StackSpecImages>) stack.get("images"));
				
				specVersion.setPipelines((List<StackSpecPipelines>) versionedStackMap.get(name));
				System.out.println("packageStackObjects one specVersion: "+specVersion);
				versions.add(specVersion);
				updateStacks.add(stackObj);
			}
		}
		return updateStacks;
	}


}
